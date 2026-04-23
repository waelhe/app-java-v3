'use client';

import React, { useState, useRef, useEffect } from 'react';
import { 
  Building2, Home, Car, Plane, FileText, Shield,
  Star, Heart, ChevronLeft, ChevronRight, Award, Grid3X3
} from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer, DrawerContent, DrawerHeader, DrawerTitle } from '@/components/ui/drawer';

interface Office {
  id: string;
  name: string;
  nameEn: string;
  type: string;
  typeEn: string;
  category: 'realestate' | 'cars' | 'travel' | 'translation' | 'insurance';
  image: string;
  location: string;
  phone: string;
  services: string[];
  servicesEn: string[];
  rating: number;
  reviews: number;
  verified?: boolean;
  featured?: boolean;
  new?: boolean;
}

const qudsayaCenterOffices: Office[] = [
  { id: 're1', name: 'مكتب العقارات الذهبي', nameEn: 'Golden Real Estate', type: 'مكتب عقاري', typeEn: 'Real Estate', category: 'realestate', image: 'https://images.unsplash.com/photo-1560518883-ce09059eeffa?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الساحة', phone: '0999111101', services: ['بيع', 'إيجار', 'إدارة عقارات'], servicesEn: ['Sale', 'Rent', 'Management'], rating: 4.9, reviews: 185, verified: true, featured: true },
  { id: 're2', name: 'مكتب الأمانة العقاري', nameEn: 'Al-Amana Real Estate', type: 'مكتب عقاري', typeEn: 'Real Estate', category: 'realestate', image: 'https://images.unsplash.com/photo-1582407947304-fd86f028f716?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - شارع الرئيسي', phone: '0999111102', services: ['شقق', 'فلل', 'أراضي'], servicesEn: ['Apartments', 'Villas', 'Lands'], rating: 4.7, reviews: 125, verified: true },
  { id: 'cr1', name: 'معرض السيارات الفاخرة', nameEn: 'Luxury Cars Showroom', type: 'معرض سيارات', typeEn: 'Car Showroom', category: 'cars', image: 'https://images.unsplash.com/photo-1552519507-da3b142c6e3d?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - المدخل', phone: '0999222201', services: ['بيع سيارات', 'شراء سيارات'], servicesEn: ['Car Sales', 'Car Purchase'], rating: 4.8, reviews: 165, verified: true, featured: true },
  { id: 'tv1', name: 'وكالة السفر والسياحة', nameEn: 'Travel & Tourism Agency', type: 'وكالة سفر', typeEn: 'Travel Agency', category: 'travel', image: 'https://images.unsplash.com/photo-1436491865332-7a61a109cc05?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الساحة', phone: '0999333301', services: ['حجز طيران', 'فنادق', 'تأشيرات'], servicesEn: ['Flight Booking', 'Hotels', 'Visas'], rating: 4.7, reviews: 145, verified: true, featured: true },
  { id: 'tr1', name: 'مكتب الترجمة والمعاملات', nameEn: 'Translation & Transactions', type: 'ترجمة ومعاملات', typeEn: 'Translation Office', category: 'translation', image: 'https://images.unsplash.com/photo-1450101499163-c8848c66ca85?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الساحة', phone: '0999444401', services: ['ترجمة', 'تصديق', 'معاملات'], servicesEn: ['Translation', 'Authentication', 'Transactions'], rating: 4.8, reviews: 120, verified: true, featured: true },
  { id: 'in1', name: 'شركة التأمين الشاملة', nameEn: 'Comprehensive Insurance', type: 'تأمين', typeEn: 'Insurance', category: 'insurance', image: 'https://images.unsplash.com/photo-1450101499163-c8848c66ca85?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الساحة', phone: '0999555501', services: ['تأمين سيارات', 'تأمين صحي'], servicesEn: ['Car Insurance', 'Health Insurance'], rating: 4.6, reviews: 105, verified: true },
];

const qudsayaDahiaOffices: Office[] = [
  { id: 'dre1', name: 'مكتب عقارات الضاحية', nameEn: 'Dahia Real Estate', type: 'مكتب عقاري', typeEn: 'Real Estate', category: 'realestate', image: 'https://images.unsplash.com/photo-1560518883-ce09059eeffa?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - الساحة', phone: '0999111104', services: ['بيع', 'إيجار', 'إدارة'], servicesEn: ['Sale', 'Rent', 'Management'], rating: 4.8, reviews: 155, verified: true, featured: true },
  { id: 'dcr1', name: 'معرض الضاحية للسيارات', nameEn: 'Dahia Cars Showroom', type: 'معرض سيارات', typeEn: 'Car Showroom', category: 'cars', image: 'https://images.unsplash.com/photo-1552519507-da3b142c6e3d?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - المدخل', phone: '0999222203', services: ['بيع', 'شراء', 'تبديل'], servicesEn: ['Sale', 'Purchase', 'Exchange'], rating: 4.7, reviews: 138, verified: true, featured: true },
  { id: 'dtv1', name: 'وكالة الضاحية للسفر', nameEn: 'Dahia Travel Agency', type: 'وكالة سفر', typeEn: 'Travel Agency', category: 'travel', image: 'https://images.unsplash.com/photo-1436491865332-7a61a109cc05?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - الساحة', phone: '0999333303', services: ['حجز طيران', 'فنادق'], servicesEn: ['Flight Booking', 'Hotels'], rating: 4.5, reviews: 82 },
  { id: 'dtr1', name: 'مكتب الترجمة - الضاحية', nameEn: 'Translation - Dahia', type: 'ترجمة ومعاملات', typeEn: 'Translation Office', category: 'translation', image: 'https://images.unsplash.com/photo-1450101499163-c8848c66ca85?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - الساحة', phone: '0999444403', services: ['ترجمة', 'تصديق'], servicesEn: ['Translation', 'Authentication'], rating: 4.6, reviews: 68 },
  { id: 'din1', name: 'وكالة التأمين - الضاحية', nameEn: 'Insurance - Dahia', type: 'تأمين', typeEn: 'Insurance', category: 'insurance', image: 'https://images.unsplash.com/photo-1450101499163-c8848c66ca85?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - الساحة', phone: '0999555503', services: ['تأمين سيارات', 'تأمين صحي'], servicesEn: ['Car Insurance', 'Health Insurance'], rating: 4.5, reviews: 72, new: true },
];

const dataByRegion: Record<Region, Office[]> = {
  'qudsaya-center': qudsayaCenterOffices,
  'qudsaya-dahia': qudsayaDahiaOffices
};

const categoryFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Building2 },
  { id: 'realestate', name: 'عقارات', nameEn: 'Real Estate', icon: Home },
  { id: 'cars', name: 'سيارات', nameEn: 'Cars', icon: Car },
  { id: 'travel', name: 'سفر', nameEn: 'Travel', icon: Plane },
  { id: 'translation', name: 'ترجمة', nameEn: 'Translation', icon: FileText },
  { id: 'insurance', name: 'تأمين', nameEn: 'Insurance', icon: Shield },
];

export default function Offices() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const offices = dataByRegion[region];
  
  const [activeCategory, setActiveCategory] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  
  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredOffices = offices.filter(o => activeCategory === 'all' || o.category === activeCategory);

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
  }, [filteredOffices]);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({ left: direction === 'left' ? -280 : 280, behavior: 'smooth' });
    }
  };

  const toggleFavorite = (id: string) => {
    setFavorites(prev => prev.includes(id) ? prev.filter(f => f !== id) : [...prev, id]);
  };

  return (
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Section Title */}
        <h2 className="text-xl font-bold text-gray-900 mb-4">
          {isArabic ? 'مكاتب' : 'Offices'}
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
                  isActive ? 'border-gray-900 text-gray-900' : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-200'
                }`}
              >
                <Icon className="w-5 h-5" />
                <span className="text-xs font-medium">{isArabic ? filter.name : filter.nameEn}</span>
              </button>
            );
          })}
        </div>

        <div className="relative">
          {canScrollLeft && (
            <button onClick={() => scroll('left')} className="absolute left-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform">
              <ChevronRight className="w-4 h-4 text-gray-700" />
            </button>
          )}
          {canScrollRight && (
            <button onClick={() => scroll('right')} className="absolute right-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform">
              <ChevronLeft className="w-4 h-4 text-gray-700" />
            </button>
          )}

          <div ref={scrollRef} className="flex gap-4 overflow-x-auto pb-2 scroll-smooth scrollbar-hide" style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}>
            {filteredOffices.slice(0, 8).map((office) => {
              const isFavorite = favorites.includes(office.id);
              return (
                <div key={office.id} className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer">
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img src={office.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                    <button onClick={(e) => { e.stopPropagation(); toggleFavorite(office.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                      <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                    </button>
                    <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-slate-600/90 text-white">
                      {isArabic ? office.type : office.typeEn}
                    </span>
                    {office.verified && (
                      <span className="absolute bottom-2 left-2 px-2 py-0.5 bg-blue-500/90 text-white text-[9px] font-semibold rounded-full backdrop-blur-sm flex items-center gap-0.5">
                        <Award className="w-2.5 h-2.5" /> {isArabic ? 'موثق' : 'Verified'}
                      </span>
                    )}
                    {office.featured && (
                      <span className="absolute bottom-2 right-2 px-2 py-0.5 bg-amber-500/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm">
                        {isArabic ? 'مميز' : 'Featured'}
                      </span>
                    )}
                  </div>
                  <div>
                    <div className="flex items-center gap-0.5 mb-0.5">
                      <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                      <span className="text-xs font-medium">{office.rating}</span>
                      <span className="text-xs text-gray-400">({office.reviews})</span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">{isArabic ? office.name : office.nameEn}</h3>
                    <div className="flex flex-wrap gap-1 mb-1">
                      {(isArabic ? office.services : office.servicesEn).slice(0, 2).map((srv, i) => (
                        <span key={i} className="text-[9px] px-2 py-0.5 bg-slate-100 text-slate-700 rounded-full">{srv}</span>
                      ))}
                    </div>
                    <div className="flex items-center justify-between text-xs text-gray-500">
                      <span className="truncate flex-1">📍 {office.location}</span>
                      <a href={`tel:${office.phone}`} onClick={(e) => e.stopPropagation()} className="text-slate-600 hover:underline flex-shrink-0">📞</a>
                    </div>
                  </div>
                </div>
              );
            })}
            
            {/* بطاقة عرض الكل */}
            {filteredOffices.length > 8 && (
              <div 
                onClick={() => setIsDrawerOpen(true)}
                className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
              >
                <div className="relative aspect-square rounded-xl overflow-hidden mb-2 border-2 border-dashed border-gray-300 hover:border-gray-400 transition-colors">
                  <div className="grid grid-cols-2 gap-0.5 h-full">
                    {filteredOffices.slice(0, 4).map((o, idx) => (
                      <div key={idx} className="relative overflow-hidden">
                        <img src={o.image} alt="" className="w-full h-full object-cover opacity-80" />
                      </div>
                    ))}
                  </div>
                  <div className="absolute inset-0 bg-black/40 flex flex-col items-center justify-center">
                    <span className="text-white text-lg font-bold">+{filteredOffices.length - 8}</span>
                    <span className="text-white text-sm mt-1">{isArabic ? 'عرض الكل' : 'View All'}</span>
                  </div>
                </div>
                <div className="text-center">
                  <p className="text-sm text-gray-600 font-medium">{isArabic ? 'تصفح جميع المكاتب' : 'Browse all offices'}</p>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Drawer لعرض جميع المكاتب */}
      <Drawer open={isDrawerOpen} onOpenChange={setIsDrawerOpen}>
        <DrawerContent className="max-h-[85vh]">
          <DrawerHeader className="border-b">
            <DrawerTitle className="flex items-center gap-2">
              🏢 {isArabic ? 'جميع المكاتب' : 'All Offices'}
            </DrawerTitle>
          </DrawerHeader>
          <div className="p-4 overflow-y-auto">
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-4">
              {filteredOffices.map((office) => {
                const isFavorite = favorites.includes(office.id);
                return (
                  <div key={office.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={office.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(office.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-slate-600/90 text-white">
                        {isArabic ? office.type : office.typeEn}
                      </span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? office.name : office.nameEn}</h3>
                    <p className="text-xs text-gray-500 truncate">📍 {office.location}</p>
                  </div>
                );
              })}
            </div>
          </div>
        </DrawerContent>
      </Drawer>
    </section>
  );
}
