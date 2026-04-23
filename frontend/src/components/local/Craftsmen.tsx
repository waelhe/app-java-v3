'use client';

import React, { useState, useRef, useEffect } from 'react';
import { Wrench, Phone, Star, Heart, ChevronLeft, ChevronRight, Shield, Zap, Droplets, Hammer, Paintbrush, Settings } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer } from 'vaul';

interface Craftsman {
  id: string;
  name: string;
  nameEn: string;
  profession: string;
  professionEn: string;
  professionCategory: 'electrician' | 'plumber' | 'carpenter' | 'painter' | 'ac' | 'general';
  rating: number;
  reviews: number;
  images: string[];
  available: boolean;
  phone: string;
  location: string;
  experience: string;
  verified?: boolean;
  featured?: boolean;
}

const qudsayaCenterCraftsmen: Craftsman[] = [
  { id: '1', name: 'أحمد الكهربائي', nameEn: 'Ahmed Electrician', profession: 'كهربائي', professionEn: 'Electrician', professionCategory: 'electrician', rating: 4.9, reviews: 160, images: ['https://images.unsplash.com/photo-1621905251189-08b45d6a269e?auto=format&fit=crop&w=400&q=80'], available: true, phone: '0999123456', location: 'قدسيا - المركز', experience: '15 سنة', verified: true, featured: true },
  { id: '2', name: 'محمود السباك', nameEn: 'Mahmoud Plumber', profession: 'سباك', professionEn: 'Plumber', professionCategory: 'plumber', rating: 4.7, reviews: 95, images: ['https://images.unsplash.com/photo-1585704032915-c3400ca199e7?auto=format&fit=crop&w=400&q=80'], available: true, phone: '0998765432', location: 'قدسيا', experience: '10 سنوات', verified: true },
  { id: '3', name: 'خالد النجار', nameEn: 'Khaled Carpenter', profession: 'نجار', professionEn: 'Carpenter', professionCategory: 'carpenter', rating: 4.8, reviews: 70, images: ['https://images.unsplash.com/photo-1504148455328-c376907d081c?auto=format&fit=crop&w=400&q=80'], available: false, phone: '0997654321', location: 'قدسيا - الحي الغربي', experience: '12 سنة', featured: true },
  { id: '4', name: 'يوسف الدهان', nameEn: 'Youssef Painter', profession: 'دهان', professionEn: 'Painter', professionCategory: 'painter', rating: 4.6, reviews: 50, images: ['https://images.unsplash.com/photo-1562259949-e8e7689d7828?auto=format&fit=crop&w=400&q=80'], available: true, phone: '0996543210', location: 'قدسيا', experience: '8 سنوات' },
  { id: '5', name: 'سامي فني تكييف', nameEn: 'Sami AC Technician', profession: 'فني تكييف', professionEn: 'AC Technician', professionCategory: 'ac', rating: 4.9, reviews: 85, images: ['https://images.unsplash.com/photo-1585338107529-13afc5f52586?auto=format&fit=crop&w=400&q=80'], available: true, phone: '0999555666', location: 'قدسيا - الشارع الرئيسي', experience: '7 سنوات', verified: true, featured: true },
  { id: '6', name: 'عمر صيانة عامة', nameEn: 'Omar General Maintenance', profession: 'صيانة عامة', professionEn: 'General Maintenance', professionCategory: 'general', rating: 4.5, reviews: 45, images: ['https://images.unsplash.com/photo-1558618666-fcd25c85cd64?auto=format&fit=crop&w=400&q=80'], available: true, phone: '0999777888', location: 'قدسيا', experience: '5 سنوات' },
];

const qudsayaDahiaCraftsmen: Craftsman[] = [
  { id: '1', name: 'سامي الكهربائي', nameEn: 'Sami Electrician', profession: 'كهربائي', professionEn: 'Electrician', professionCategory: 'electrician', rating: 4.8, reviews: 145, images: ['https://images.unsplash.com/photo-1621905251189-08b45d6a269e?auto=format&fit=crop&w=400&q=80'], available: true, phone: '0999234567', location: 'الضاحية', experience: '13 سنة', verified: true, featured: true },
  { id: '2', name: 'عمر السباك', nameEn: 'Omar Plumber', profession: 'سباك', professionEn: 'Plumber', professionCategory: 'plumber', rating: 4.9, reviews: 110, images: ['https://images.unsplash.com/photo-1585704032915-c3400ca199e7?auto=format&fit=crop&w=400&q=80'], available: true, phone: '0998345678', location: 'الضاحية - الحي الرئيسي', experience: '11 سنة', verified: true, featured: true },
  { id: '3', name: 'فادي النجار', nameEn: 'Fadi Carpenter', profession: 'نجار', professionEn: 'Carpenter', professionCategory: 'carpenter', rating: 4.7, reviews: 85, images: ['https://images.unsplash.com/photo-1504148455328-c376907d081c?auto=format&fit=crop&w=400&q=80'], available: true, phone: '0997456789', location: 'الضاحية', experience: '10 سنوات' },
  { id: '4', name: 'رامي الدهان', nameEn: 'Rami Painter', profession: 'دهان', professionEn: 'Painter', professionCategory: 'painter', rating: 4.5, reviews: 60, images: ['https://images.unsplash.com/photo-1562259949-e8e7689d7828?auto=format&fit=crop&w=400&q=80'], available: false, phone: '0996567890', location: 'الضاحية', experience: '7 سنوات' },
  { id: '5', name: 'حسان فني تكييف', nameEn: 'Hassan AC Technician', profession: 'فني تكييف', professionEn: 'AC Technician', professionCategory: 'ac', rating: 4.8, reviews: 92, images: ['https://images.unsplash.com/photo-1585338107529-13afc5f52586?auto=format&fit=crop&w=400&q=80'], available: true, phone: '0999666777', location: 'الضاحية - المركز التجاري', experience: '6 سنوات', verified: true },
];

const dataByRegion: Record<Region, Craftsman[]> = {
  'qudsaya-center': qudsayaCenterCraftsmen,
  'qudsaya-dahia': qudsayaDahiaCraftsmen
};

const categoryFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Wrench },
  { id: 'electrician', name: 'كهربائيين', nameEn: 'Electricians', icon: Zap },
  { id: 'plumber', name: 'سباكين', nameEn: 'Plumbers', icon: Droplets },
  { id: 'carpenter', name: 'نجارين', nameEn: 'Carpenters', icon: Hammer },
  { id: 'painter', name: 'دهانين', nameEn: 'Painters', icon: Paintbrush },
  { id: 'ac', name: 'تكييف', nameEn: 'AC Technicians', icon: Settings },
];

export default function Craftsmen() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const craftsmen = dataByRegion[region];
  
  const [activeCategory, setActiveCategory] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [showAll, setShowAll] = useState(false);
  
  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredCraftsmen = craftsmen.filter(craftsman => activeCategory === 'all' || craftsman.professionCategory === activeCategory);

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
  }, [filteredCraftsmen]);

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
            {filteredCraftsmen.slice(0, 8).map((craftsman) => {
              const isFavorite = favorites.includes(craftsman.id);
              return (
                <div key={craftsman.id} className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer">
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img src={craftsman.images[0]} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                    <button onClick={(e) => { e.stopPropagation(); toggleFavorite(craftsman.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                      <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                    </button>
                    <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${craftsman.available ? 'bg-emerald-500/90 text-white' : 'bg-gray-500/90 text-white'}`}>
                      {craftsman.available ? (isArabic ? 'متاح' : 'Available') : (isArabic ? 'مشغول' : 'Busy')}
                    </span>
                    {craftsman.verified && (
                      <span className="absolute bottom-2 left-2 px-2 py-0.5 bg-blue-500/90 text-white text-[9px] font-semibold rounded-full backdrop-blur-sm flex items-center gap-0.5">
                        <Shield className="w-2.5 h-2.5" /> {isArabic ? 'موثق' : 'Verified'}
                      </span>
                    )}
                    {craftsman.featured && (
                      <span className="absolute bottom-2 right-2 px-2 py-0.5 bg-amber-500/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm">
                        {isArabic ? 'مميز' : 'Featured'}
                      </span>
                    )}
                  </div>
                  <div>
                    <span className="text-[10px] px-2 py-0.5 bg-stone-100 text-stone-700 rounded-full font-medium">
                      {isArabic ? craftsman.profession : craftsman.professionEn}
                    </span>
                    <h3 className="text-sm font-medium text-gray-900 mt-1 mb-0.5 line-clamp-1">{isArabic ? craftsman.name : craftsman.nameEn}</h3>
                    <div className="flex items-center gap-1 mb-1">
                      <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                      <span className="text-xs font-medium">{craftsman.rating}</span>
                      <span className="text-xs text-gray-400">({craftsman.reviews})</span>
                    </div>
                    <div className="flex items-center justify-between text-xs text-gray-500">
                      <span className="truncate flex-1">📍 {craftsman.location}</span>
                      <a href={`tel:${craftsman.phone}`} onClick={(e) => e.stopPropagation()} className="text-stone-600 hover:underline flex-shrink-0">📞</a>
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
                  {filteredCraftsmen.slice(0, 4).map((c, i) => (
                    <div key={i} className="overflow-hidden">
                      <img src={c.images[0]} alt="" className="w-full h-full object-cover" />
                    </div>
                  ))}
                </div>
                <div className="absolute inset-0 bg-black/40 group-hover:bg-black/50 transition-colors" />
                <div className="absolute inset-0 flex flex-col items-center justify-center text-white">
                  <span className="text-base font-semibold">{isArabic ? 'عرض الكل' : 'Show all'}</span>
                  <span className="text-xs text-white/80 mt-0.5">{filteredCraftsmen.length} {isArabic ? 'حرفي' : 'craftsmen'}</span>
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
              {isArabic ? 'جميع الحرفيين' : 'All Craftsmen'}
            </Drawer.Title>
            <p className="text-sm text-gray-500 text-right">
              {filteredCraftsmen.length} {isArabic ? 'حرفي متاح' : 'craftsmen available'}
            </p>
          </div>
          <div className="flex-1 overflow-y-auto px-4 py-4" style={{ maxHeight: 'calc(90vh - 100px)' }}>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">
              {filteredCraftsmen.map((craftsman) => {
                const isFavorite = favorites.includes(craftsman.id);
                return (
                  <div key={craftsman.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={craftsman.images[0]} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(craftsman.id); }} className="absolute top-2 right-2 p-1.5">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${craftsman.available ? 'bg-emerald-500/90 text-white' : 'bg-gray-500/90 text-white'}`}>
                        {craftsman.available ? (isArabic ? 'متاح' : 'Available') : (isArabic ? 'مشغول' : 'Busy')}
                      </span>
                    </div>
                    <span className="text-[10px] px-2 py-0.5 bg-stone-100 text-stone-700 rounded-full font-medium">
                      {isArabic ? craftsman.profession : craftsman.professionEn}
                    </span>
                    <h3 className="text-sm font-medium text-gray-900 mt-1 mb-0.5 line-clamp-1">{isArabic ? craftsman.name : craftsman.nameEn}</h3>
                    <div className="flex items-center gap-1 mb-1">
                      <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                      <span className="text-xs font-medium">{craftsman.rating}</span>
                      <span className="text-xs text-gray-400">({craftsman.reviews})</span>
                    </div>
                    <div className="flex items-center justify-between text-xs text-gray-500">
                      <span className="truncate flex-1">📍 {craftsman.location}</span>
                      <a href={`tel:${craftsman.phone}`} onClick={(e) => e.stopPropagation()} className="text-stone-600 hover:underline flex-shrink-0">📞</a>
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
