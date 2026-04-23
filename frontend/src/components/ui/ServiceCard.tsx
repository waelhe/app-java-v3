'use client';

import React, { useState } from 'react';
import { Star, MapPin, Heart, ChevronRight, ChevronLeft } from 'lucide-react';
import { motion } from 'framer-motion';
import { useLanguage } from '@/contexts/LanguageContext';

// Listing type based on the API response
export interface Listing {
  id: string;
  title: string;
  slug: string;
  type: string;
  city?: string;
  country?: string;
  basePrice: number;
  currency: string;
  capacity: number;
  bedrooms?: number;
  bathrooms?: number;
  ratingAverage?: number;
  ratingCount: number;
  featured?: boolean;
  images: Array<{ id: string; url: string; isPrimary: boolean }>;
  amenities: Array<{ name: string }>;
  host?: {
    firstName: string;
    lastName: string;
    avatar?: string;
  };
}

interface ServiceCardProps {
  listing: Listing;
  activeColorClass?: string;
  activeTextClass?: string;
  activeBgLightClass?: string;
  className?: string;
}

export default function ServiceCard({ 
  listing, 
  activeColorClass = 'bg-emerald-600', 
  activeTextClass = 'text-emerald-600', 
  activeBgLightClass = 'bg-emerald-50',
  className = "w-[280px] md:w-[320px]"
}: ServiceCardProps) {
  const [currentImageIndex, setCurrentImageIndex] = useState(0);
  const [isHovered, setIsHovered] = useState(false);
  const [isFavorite, setIsFavorite] = useState(false);
  const { language } = useLanguage();

  const images = listing.images || [];
  const hasImages = images.length > 0;

  const nextImage = (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (hasImages) {
      setCurrentImageIndex((prev) => (prev + 1) % images.length);
    }
  };

  const prevImage = (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (hasImages) {
      setCurrentImageIndex((prev) => (prev - 1 + images.length) % images.length);
    }
  };

  const toggleFavorite = (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsFavorite(!isFavorite);
  };

  const formatPrice = (price: number, currency: string) => {
    return new Intl.NumberFormat(language === 'ar' ? 'ar-SY' : 'en-US', {
      style: 'currency',
      currency: currency || 'SYP',
      maximumFractionDigits: 0,
    }).format(price);
  };

  const currentImage = hasImages ? images[currentImageIndex]?.url : null;
  const location = [listing.city, listing.country].filter(Boolean).join('، ');

  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      className={`group flex flex-col shrink-0 cursor-pointer ${className}`}
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
    >
      <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
        {/* Image */}
        <div className="block w-full h-full">
          {currentImage ? (
            <img 
              src={currentImage} 
              alt={listing.title || 'Listing'} 
              className="w-full h-full object-cover transition-transform duration-500 group-hover:scale-105"
            />
          ) : (
            <div className="w-full h-full bg-gray-200 flex items-center justify-center">
              <MapPin className="w-12 h-12 text-gray-400" />
            </div>
          )}
        </div>

        {/* Carousel Controls */}
        {isHovered && hasImages && images.length > 1 && (
          <>
            <button 
              onClick={prevImage}
              className="absolute top-1/2 right-2 -translate-y-1/2 p-1 bg-white/90 backdrop-blur-sm rounded-full text-gray-900 hover:bg-white hover:scale-110 transition-all z-10 shadow-sm"
            >
              <ChevronRight className="w-4 h-4" />
            </button>
            <button 
              onClick={nextImage}
              className="absolute top-1/2 left-2 -translate-y-1/2 p-1 bg-white/90 backdrop-blur-sm rounded-full text-gray-900 hover:bg-white hover:scale-110 transition-all z-10 shadow-sm"
            >
              <ChevronLeft className="w-4 h-4" />
            </button>
          </>
        )}

        {/* Carousel Dots */}
        {hasImages && images.length > 1 && (
          <div className="absolute bottom-2 left-0 right-0 flex justify-center gap-1 z-10">
            {images.map((_, idx) => (
              <div 
                key={idx} 
                className={`h-1 w-1 rounded-full transition-all ${idx === currentImageIndex ? 'bg-white scale-125' : 'bg-white/60'}`}
              />
            ))}
          </div>
        )}

        {/* Featured Badge */}
        {listing.featured && (
          <div className="absolute top-2 right-2 z-10">
            <div className="bg-white/95 backdrop-blur-sm px-2 py-0.5 rounded-full shadow-sm border border-gray-100">
              <span className="text-[10px] font-bold text-gray-900">
                {language === 'ar' ? 'مميز' : 'Featured'}
              </span>
            </div>
          </div>
        )}

        {/* Favorite Button */}
        <button 
          onClick={toggleFavorite}
          className="absolute top-2 left-2 z-10 p-1 group/heart"
        >
          <Heart 
            className={`w-5 h-5 transition-all ${
              isFavorite 
                ? 'fill-red-500 stroke-red-500 scale-110' 
                : 'text-white stroke-[2px] drop-shadow-md group-hover/heart:scale-110'
            }`} 
          />
        </button>
      </div>
      
      <div className="flex flex-col gap-0.5">
        <div className="flex justify-between items-start">
          <div className="flex-1 min-w-0">
            <h3 className="text-[14px] font-bold text-gray-900 truncate">
              {listing.title}
            </h3>
          </div>
          {listing.ratingAverage && (
            <div className="flex items-center gap-1 shrink-0">
              <Star className="w-3 h-3 fill-current text-gray-900" />
              <span className="text-xs font-medium text-gray-900">{listing.ratingAverage.toFixed(1)}</span>
            </div>
          )}
        </div>
        
        {location && (
          <p className="text-[13px] text-gray-500 truncate">
            {location}
          </p>
        )}
        
        <div className="mt-0.5 flex items-center gap-1">
          <span className="text-[14px] font-bold text-gray-900">{formatPrice(listing.basePrice, listing.currency)}</span>
          <span className="text-[13px] text-gray-600">
            {language === 'ar' ? 'مقابل 2 ليال' : 'for 2 nights'}
          </span>
        </div>
      </div>
    </motion.div>
  );
}
