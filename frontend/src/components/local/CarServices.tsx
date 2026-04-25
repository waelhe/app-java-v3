'use client';

import React, { useState, useRef, useEffect } from 'react';
import { 
  Car, Droplets, Wrench, Battery, CircleDot, Paintbrush, Fuel,
  Star, Heart, ChevronLeft, ChevronRight, Zap
} from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer } from 'vaul';

interface CarService {
  id: string;
  name: string;
  nameEn: string;
  type: string;
  typeEn: string;
  category: 'carwash' | 'mechanic' | 'electric' | 'tires' | 'paint' | 'gas' | 'quick-service';
  image: string;
  location: string;
  hours?: string;
  phone: string;
  services: string[];
  servicesEn: string[];
  rating: number;
  reviews?: number;
  price?: string;
  featured?: boolean;
  new?: boolean;
  available?: boolean;
  isOpen?: boolean;
  is24h?: boolean;
  hasWash?: boolean;
  hasOil?: boolean;
  hasAir?: boolean;
}

const qudsayaCenterServices: CarService[] = [
  // Gas Stations
  {
    id: 'gas-c1',
    name: 'محطة بنزين القدس',
    nameEn: 'Al-Quds Gas Station',
    type: 'محطة بنزين',
    typeEn: 'Gas Station',
    category: 'gas',
    image: 'https://images.unsplash.com/photo-1605101100278-5d1deb2b6498?auto=format&fit=crop&w=400&q=80',
    location: 'طريق قدسيا الرئيسي',
    phone: '0999444555',
    services: ['بنزين', 'غسيل', 'زيت'],
    servicesEn: ['Gas', 'Wash', 'Oil'],
    rating: 4.6,
    featured: true,
    available: true,
    isOpen: true,
    is24h: true,
    hasWash: true,
    hasOil: true,
    hasAir: true
  },
  // Car Wash
  {
    id: 'cw1',
    name: 'مغسلة السيارات الفاخرة',
    nameEn: 'Luxury Car Wash',
    type: 'مغسلة سيارات',
    typeEn: 'Car Wash',
    category: 'carwash',
    image: 'https://images.unsplash.com/photo-1520340356584-f9917d1eea6f?auto=format&fit=crop&w=400&q=80',
    location: 'قدسيا - المدخل الرئيسي',
    hours: '7:00 - 21:00',
    phone: '0999111101',
    services: ['غسيل خارجي', 'تلميع', 'تنظيف داخلي'],
    servicesEn: ['Exterior Wash', 'Polishing', 'Interior Cleaning'],
    rating: 4.9,
    reviews: 180,
    price: '500 - 2000',
    featured: true,
    available: true
  },
  // Mechanic
  {
    id: 'mc1',
    name: 'ورشة الميكانيك الشامل',
    nameEn: 'General Mechanic Workshop',
    type: 'ميكانيك',
    typeEn: 'Mechanic',
    category: 'mechanic',
    image: 'https://images.unsplash.com/photo-1619642751034-765dfdf7c58e?auto=format&fit=crop&w=400&q=80',
    location: 'قدسيا - المنطقة الصناعية',
    hours: '8:00 - 18:00',
    phone: '0999222201',
    services: ['صيانة عامة', 'محركات', 'فرامل'],
    servicesEn: ['General Maintenance', 'Engines', 'Brakes'],
    rating: 4.8,
    reviews: 150,
    featured: true,
    available: true
  },
  // Quick Service
  {
    id: 'qs-c1',
    name: 'ورشة الصفاء',
    nameEn: 'Al-Safa Workshop',
    type: 'صيانة سريعة',
    typeEn: 'Quick Service',
    category: 'quick-service',
    image: 'https://images.unsplash.com/photo-1486262715619-67b85e0b08d3?auto=format&fit=crop&w=400&q=80',
    location: 'الحي الغربي - قدسيا',
    phone: '0999666777',
    services: ['زيت', 'هواء', 'فحص'],
    servicesEn: ['Oil', 'Air', 'Check'],
    rating: 4.3,
    available: true,
    hasOil: true,
    hasAir: true
  },
  // Electric
  {
    id: 'el1',
    name: 'كهرباء السيارات الحديثة',
    nameEn: 'Modern Car Electric',
    type: 'كهرباء سيارات',
    typeEn: 'Car Electric',
    category: 'electric',
    image: 'https://images.unsplash.com/photo-1625047509248-ec889cbff17f?auto=format&fit=crop&w=400&q=80',
    location: 'قدسيا - المدخل',
    hours: '8:00 - 19:00',
    phone: '0999333301',
    services: ['بطاريات', 'دينامو', 'تمامير'],
    servicesEn: ['Batteries', 'Alternators', 'Starters'],
    rating: 4.7,
    reviews: 110,
    featured: true,
    available: true
  },
  // Tires
  {
    id: 'tr1',
    name: 'مركز الإطارات الشامل',
    nameEn: 'Comprehensive Tire Center',
    type: 'إطارات وجنوط',
    typeEn: 'Tires & Rims',
    category: 'tires',
    image: 'https://images.unsplash.com/photo-1558618666-fcd25c85cd64?auto=format&fit=crop&w=400&q=80',
    location: 'قدسيا - المدخل',
    hours: '8:00 - 19:00',
    phone: '0999444401',
    services: ['بيع إطارات', 'تركيب', 'توازن'],
    servicesEn: ['Tire Sales', 'Installation', 'Balancing'],
    rating: 4.8,
    reviews: 125,
    featured: true,
    available: true
  },
  // Paint
  {
    id: 'pt1',
    name: 'دهان السيارات المتميز',
    nameEn: 'Premium Car Painting',
    type: 'دهان سيارات',
    typeEn: 'Car Painting',
    category: 'paint',
    image: 'https://images.unsplash.com/photo-1562259949-e8e7689d7828?auto=format&fit=crop&w=400&q=80',
    location: 'قدسيا - المنطقة الصناعية',
    hours: '8:00 - 17:00',
    phone: '0999555501',
    services: ['دهان كامل', 'لمعان', 'إصلاح خدوش'],
    servicesEn: ['Full Paint', 'Polishing', 'Scratch Repair'],
    rating: 4.7,
    reviews: 92,
    featured: true,
    available: true
  }
];

const qudsayaDahiaServices: CarService[] = [
  // Gas Stations
  {
    id: 'gas-d1',
    name: 'محطة بنزين الضاحية',
    nameEn: 'Dahia Gas Station',
    type: 'محطة بنزين',
    typeEn: 'Gas Station',
    category: 'gas',
    image: 'https://images.unsplash.com/photo-1605101100278-5d1deb2b6498?auto=format&fit=crop&w=400&q=80',
    location: 'الساحة الرئيسية - ضاحية قدسيا',
    phone: '0999111222',
    services: ['بنزين', 'غسيل', 'زيت'],
    servicesEn: ['Gas', 'Wash', 'Oil'],
    rating: 4.5,
    featured: true,
    available: true,
    isOpen: true,
    is24h: true,
    hasWash: true,
    hasOil: true,
    hasAir: true
  },
  // Car Wash
  {
    id: 'dcw1',
    name: 'مغسلة الضاحية',
    nameEn: 'Dahia Car Wash',
    type: 'مغسلة سيارات',
    typeEn: 'Car Wash',
    category: 'carwash',
    image: 'https://images.unsplash.com/photo-1520340356584-f9917d1eea6f?auto=format&fit=crop&w=400&q=80',
    location: 'الضاحية - المدخل',
    hours: '7:00 - 21:00',
    phone: '0999111103',
    services: ['غسيل خارجي', 'تلميع', 'شمع'],
    servicesEn: ['Exterior Wash', 'Polishing', 'Wax'],
    rating: 4.8,
    reviews: 165,
    featured: true,
    available: true
  },
  // Quick Service
  {
    id: 'qs-d1',
    name: 'مركز صيانة الضاحية',
    nameEn: 'Dahia Service Center',
    type: 'صيانة سريعة',
    typeEn: 'Quick Service',
    category: 'quick-service',
    image: 'https://images.unsplash.com/photo-1486262715619-67b85e0b08d3?auto=format&fit=crop&w=400&q=80',
    location: 'الحي الجنوبي - ضاحية قدسيا',
    phone: '0999333444',
    services: ['زيت', 'هواء', 'فحص'],
    servicesEn: ['Oil', 'Air', 'Check'],
    rating: 4.4,
    available: true,
    hasOil: true,
    hasAir: true
  },
  // Mechanic
  {
    id: 'dmc1',
    name: 'ورشة ميكانيك الضاحية',
    nameEn: 'Dahia Mechanic Workshop',
    type: 'ميكانيك',
    typeEn: 'Mechanic',
    category: 'mechanic',
    image: 'https://images.unsplash.com/photo-1619642751034-765dfdf7c58e?auto=format&fit=crop&w=400&q=80',
    location: 'الضاحية - المنطقة الصناعية',
    hours: '8:00 - 18:00',
    phone: '0999222203',
    services: ['صيانة', 'محركات', 'قير'],
    servicesEn: ['Maintenance', 'Engines', 'Transmission'],
    rating: 4.7,
    reviews: 138,
    featured: true,
    available: true
  },
  // Electric
  {
    id: 'del1',
    name: 'كهرباء سيارات الضاحية',
    nameEn: 'Dahia Car Electric',
    type: 'كهرباء سيارات',
    typeEn: 'Car Electric',
    category: 'electric',
    image: 'https://images.unsplash.com/photo-1625047509248-ec889cbff17f?auto=format&fit=crop&w=400&q=80',
    location: 'الضاحية - الحي الرئيسي',
    hours: '8:00 - 19:00',
    phone: '0999333303',
    services: ['بطاريات', 'أسلاك', 'إنذار'],
    servicesEn: ['Batteries', 'Wiring', 'Alarm'],
    rating: 4.6,
    reviews: 95,
    available: true
  }
];

const dataByRegion: Record<Region, CarService[]> = {
  'qudsaya-center': qudsayaCenterServices,
  'qudsaya-dahia': qudsayaDahiaServices
};

const categoryFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Car },
  { id: 'gas', name: 'بنزين', nameEn: 'Gas', icon: Fuel },
  { id: 'carwash', name: 'مغاسل', nameEn: 'Car Wash', icon: Droplets },
  { id: 'mechanic', name: 'ميكانيك', nameEn: 'Mechanic', icon: Wrench },
  { id: 'quick-service', name: 'صيانة سريعة', nameEn: 'Quick Service', icon: Wrench },
  { id: 'electric', name: 'كهرباء', nameEn: 'Electric', icon: Battery },
  { id: 'tires', name: 'إطارات', nameEn: 'Tires', icon: CircleDot },
  { id: 'paint', name: 'دهان', nameEn: 'Painting', icon: Paintbrush },
];

export default function CarServices() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const services = dataByRegion[region];
  
  const [activeCategory, setActiveCategory] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [showAll, setShowAll] = useState(false);
  
  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredServices = services.filter(service => {
    return activeCategory === 'all' || service.category === activeCategory;
  });

  const checkScroll = () => {
    if (scrollRef.current) {
      const { scrollLeft, scrollWidth, clientWidth } = scrollRef.current;
      setCanScrollLeft(scrollLeft > 0);
      setCanScrollRight(scrollLeft < scrollWidth - clientWidth - 10);
    }
  };

  useEffect(() => {
    checkScroll();
    const scrollEl = scrollRef.current;
    if (scrollEl) {
      scrollEl.addEventListener('scroll', checkScroll);
      return () => scrollEl.removeEventListener('scroll', checkScroll);
    }
  }, [filteredServices]);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({
        left: direction === 'left' ? -280 : 280,
        behavior: 'smooth'
      });
    }
  };

  const toggleFavorite = (id: string) => {
    setFavorites(prev => 
      prev.includes(id) ? prev.filter(f => f !== id) : [...prev, id]
    );
  };

  return (
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Section Title */}
        <h2 className="text-xl font-bold text-gray-900 mb-4">
          {isArabic ? '🚗 خدمات سيارات' : '🚗 Car Services'}
        </h2>

        {/* Category Filters - Airbnb Style */}
        <div className="flex gap-6 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          {categoryFilters.map((filter) => {
            const Icon = filter.icon;
            const isActive = activeCategory === filter.id;
            
            return (
              <button
                key={filter.id}
                onClick={() => setActiveCategory(filter.id)}
                className={`flex flex-col items-center gap-1.5 pb-2 min-w-[50px] transition-all border-b-2 ${
                  isActive 
                    ? 'border-gray-900 text-gray-900' 
                    : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-200'
                }`}
              >
                <Icon className="w-5 h-5" />
                <span className="text-xs font-medium">{isArabic ? filter.name : filter.nameEn}</span>
              </button>
            );
          })}
        </div>

        {/* Horizontal Scrolling Container - Airbnb Style */}
        <div className="relative">
          {/* Navigation Buttons */}
          {canScrollLeft && (
            <button
              onClick={() => scroll('left')}
              className="absolute left-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform"
            >
              <ChevronRight className="w-4 h-4 text-gray-700" />
            </button>
          )}
          {canScrollRight && (
            <button
              onClick={() => scroll('right')}
              className="absolute right-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform"
            >
              <ChevronLeft className="w-4 h-4 text-gray-700" />
            </button>
          )}

          {/* Scrollable Cards */}
          <div
            ref={scrollRef}
            className="flex gap-4 overflow-x-auto pb-2 scroll-smooth scrollbar-hide"
            style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}
          >
            {filteredServices.slice(0, 8).map((service) => {
              const isFavorite = favorites.includes(service.id);
              
              return (
                <div
                  key={service.id}
                  className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
                >
                  {/* Image Container - Airbnb Style */}
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img 
                      src={service.image} 
                      alt="" 
                      className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                    />
                    
                    {/* Favorite Button */}
                    <button
                      onClick={(e) => { e.stopPropagation(); toggleFavorite(service.id); }}
                      className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110"
                    >
                      <Heart 
                        className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} 
                      />
                    </button>

                    {/* Type Badge */}
                    <div className="absolute top-2 left-2">
                      <span className={`px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${
                        service.category === 'gas' ? 'bg-green-500/90 text-white' : 'bg-sky-600/90 text-white'
                      }`}>
                        {isArabic ? service.type : service.typeEn}
                      </span>
                    </div>

                    {/* Status Badge for Gas Stations */}
                    {service.isOpen !== undefined && (
                      <span className={`absolute top-10 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${
                        service.isOpen ? 'bg-emerald-500/90 text-white' : 'bg-gray-500/90 text-white'
                      }`}>
                        {service.isOpen ? (isArabic ? 'مفتوح' : 'Open') : (isArabic ? 'مغلق' : 'Closed')}
                      </span>
                    )}

                    {/* 24h Badge */}
                    {service.is24h && (
                      <span className="absolute top-2 right-10 px-2 py-0.5 bg-green-600/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm">
                        24H
                      </span>
                    )}

                    {/* Available Badge */}
                    {service.available !== false && !service.isOpen && (
                      <span className="absolute bottom-2 right-2 px-2 py-0.5 text-[9px] font-semibold rounded-full backdrop-blur-sm bg-emerald-500/90 text-white flex items-center gap-0.5">
                        <Zap className="w-2.5 h-2.5" />
                        {isArabic ? 'متاح' : 'Open'}
                      </span>
                    )}

                    {/* Featured/New Badge */}
                    {(service.featured || service.new) && (
                      <div className="absolute bottom-2 left-2">
                        <span className={`px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${
                          service.featured ? 'bg-amber-500/90 text-white' : 'bg-blue-500/90 text-white'
                        }`}>
                          {service.featured ? (isArabic ? 'مميز' : 'Featured') : (isArabic ? 'جديد' : 'New')}
                        </span>
                      </div>
                    )}

                    {/* Features Badges for Gas Stations */}
                    {service.category === 'gas' && (
                      <div className="absolute bottom-2 right-2 flex gap-1">
                        {service.hasWash && (
                          <span className="p-1 bg-white/90 rounded-full backdrop-blur-sm">
                            <Car className="w-3 h-3 text-blue-600" />
                          </span>
                        )}
                        {service.hasOil && (
                          <span className="p-1 bg-white/90 rounded-full backdrop-blur-sm">
                            <Droplets className="w-3 h-3 text-amber-600" />
                          </span>
                        )}
                        {service.hasAir && (
                          <span className="p-1 bg-white/90 rounded-full backdrop-blur-sm">
                            <Wrench className="w-3 h-3 text-gray-600" />
                          </span>
                        )}
                      </div>
                    )}
                  </div>

                  {/* Service Info - Airbnb Style */}
                  <div>
                    {/* Rating */}
                    <div className="flex items-center gap-0.5 mb-0.5">
                      <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                      <span className="text-xs font-medium">{service.rating}</span>
                      {service.reviews && <span className="text-xs text-gray-400">({service.reviews})</span>}
                    </div>

                    {/* Title */}
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">
                      {isArabic ? service.name : service.nameEn}
                    </h3>

                    {/* Services Tags */}
                    <div className="flex flex-wrap gap-1 mb-1">
                      {(isArabic ? service.services : service.servicesEn).slice(0, 2).map((srv, i) => (
                        <span key={i} className="text-[9px] px-2 py-0.5 bg-sky-100 text-sky-700 rounded-full">
                          {srv}
                        </span>
                      ))}
                    </div>

                    {/* Location & Phone */}
                    <div className="flex items-center justify-between">
                      <span className="text-xs text-gray-500 truncate flex-1">
                        📍 {service.location}
                      </span>
                      <a
                        href={`tel:${service.phone}`}
                        onClick={(e) => e.stopPropagation()}
                        className="text-xs text-sky-600 hover:underline flex-shrink-0"
                      >
                        📞
                      </a>
                    </div>
                  </div>
                </div>
              );
            })}
            
            {/* View All Card */}
            <button
              onClick={() => setShowAll(true)}
              className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
            >
              <div className="aspect-square rounded-xl overflow-hidden relative bg-gray-100">
                <div className="grid grid-cols-2 grid-rows-2 h-full gap-0.5">
                  {filteredServices.slice(0, 4).map((s, i) => (
                    <div key={i} className="overflow-hidden">
                      <img src={s.image} alt="" className="w-full h-full object-cover" />
                    </div>
                  ))}
                </div>
                <div className="absolute inset-0 bg-black/40 group-hover:bg-black/50 transition-colors" />
                <div className="absolute inset-0 flex flex-col items-center justify-center text-white">
                  <span className="text-base font-semibold">{isArabic ? 'عرض الكل' : 'Show all'}</span>
                  <span className="text-xs text-white/80 mt-0.5">{filteredServices.length} {isArabic ? 'خدمة' : 'services'}</span>
                </div>
              </div>
            </button>
          </div>
        </div>
      </div>

      {/* All Items Drawer */}
      <Drawer.Root open={showAll} onOpenChange={setShowAll}>
        <Drawer.Content className="fixed inset-x-0 bottom-0 h-[90vh] bg-white rounded-t-3xl shadow-2xl z-50">
          <div className="px-4 pt-4 pb-3 border-b border-gray-100">
            <Drawer.Handle className="w-10 h-1 bg-gray-300 rounded-full mx-auto mb-3 block" />
            <Drawer.Title className="text-lg font-bold text-right">
              {isArabic ? 'جميع خدمات السيارات' : 'All Car Services'}
            </Drawer.Title>
            <p className="text-sm text-gray-500 text-right">
              {filteredServices.length} {isArabic ? 'خدمة متاحة' : 'services available'}
            </p>
          </div>
          <div className="flex-1 overflow-y-auto px-4 py-4" style={{ maxHeight: 'calc(90vh - 100px)' }}>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">
              {filteredServices.map((service) => {
                const isFavorite = favorites.includes(service.id);
                return (
                  <div key={service.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={service.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(service.id); }} className="absolute top-2 right-2 p-1.5">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${service.category === 'gas' ? 'bg-green-500/90 text-white' : 'bg-sky-600/90 text-white'}`}>
                        {isArabic ? service.type : service.typeEn}
                      </span>
                    </div>
                    <div className="flex items-center gap-0.5 mb-0.5">
                      <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                      <span className="text-xs font-medium">{service.rating}</span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? service.name : service.nameEn}</h3>
                    <div className="flex flex-wrap gap-1 mb-1">
                      {(isArabic ? service.services : service.servicesEn).slice(0, 2).map((srv, i) => (
                        <span key={i} className="text-[9px] px-2 py-0.5 bg-sky-100 text-sky-700 rounded-full">{srv}</span>
                      ))}
                    </div>
                    <div className="flex items-center justify-between text-xs text-gray-500">
                      <span className="truncate flex-1">📍 {service.location}</span>
                      <a href={`tel:${service.phone}`} onClick={(e) => e.stopPropagation()} className="text-sky-600 hover:underline flex-shrink-0">📞</a>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </Drawer.Content>
      </Drawer.Root>
    </section>
  );
}
